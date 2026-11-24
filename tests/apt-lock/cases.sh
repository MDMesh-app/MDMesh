#!/usr/bin/env bash
# The cases tests/apt-lock/run.sh runs inside its throwaway root container (see there). Not for any other host: they
# install packages and run ./setup.sh --update --native as root.
#
# Another apt run is played by a process holding apt's lock files with fcntl, as apt does (perl, which every Debian and
# Ubuntu image has): /var/lib/apt/lists/lock (apt-get update), /var/cache/apt/archives/lock and /var/lib/dpkg/lock-frontend
# (apt-get install). The cases, in order:
#   timeout   the lists lock held past the wait (shortened to 12 s in a copy of install/lib/apt.sh): ./setup.sh
#             --update --native stops, saying so, and nothing is installed;
#   setup     the lists lock held 15 s and the archives lock 35 s and until 10 s after the refresh, empty package
#             lists: --update waits for both, then refreshes and installs what it needs (curl, ca-certificates, openssl),
#             never "failed to refresh";
#   quiet     the dpkg frontend lock held 10 s, apt's output hidden (setup.sh's Docker-mode minisign install): the
#             waiting is still said, then minisign is installed;
#   native    all three held 15 s: the native installer (a copy that stops after its apt steps, on a terminal) waits,
#             refreshes and installs;
#   jdk       the oldest openjdk-17 apt has, then that copy's update-only JDK 17 upgrade, with apt in German (a generated
#             en_US.UTF-8 locale, LANGUAGE=de, apt's translations put back): it moves to apt's newest openjdk-17 and
#             says so.
set -euo pipefail
if [ "${MDMESH_APT_LOCK_TESTS:-}" != 1 ] || [ ! -f /.dockerenv ] || [ "$(id -u)" != 0 ]; then
  echo "cases.sh runs only in tests/apt-lock/run.sh's container." >&2; exit 2
fi
export DEBIAN_FRONTEND=noninteractive LC_ALL=C
sh /src/tests/lib/ubuntu-mirror.sh   # CI: the runner's own Ubuntu mirror (empty: the image's)
FAILS=0; CHECKS=0
fail() { FAILS=$((FAILS + 1)); printf 'FAIL: %s\n' "$*" >&2; }
# Each check: `t; CONDITION || bad WHAT`.
t() { CHECKS=$((CHECKS + 1)); }
bad() { fail "$*"; show; }
# shown: with MDMESH_APT_LOCK_SHOW=1 (tests/apt-lock/run.sh IMAGE show), each case's output.
shown() { [ "${MDMESH_APT_LOCK_SHOW:-}" != 1 ] || { echo "-- $1:" >&2; show; }; }
show() { sed 's/^/    | /' "$OUT" >&2; }

# Recommends off (as on a minimal image), for every apt-get here and in the installers.
printf 'APT::Install-Recommends "false";\n' > /tmp/norecommends.conf
export APT_CONFIG=/tmp/norecommends.conf
# git, from package lists kept apart, so the image's own stay empty (as on most LXC templates and minimal images).
mkdir -p /tmp/lists/partial
apt-get -qq -o Dir::State::Lists=/tmp/lists update > /tmp/git-install.log 2>&1
apt-get -qq -o Dir::State::Lists=/tmp/lists install -y git >> /tmp/git-install.log 2>&1 || { cat /tmp/git-install.log; exit 1; }
for c in curl openssl python3 minisign; do ! command -v "$c" > /tmp/ignored 2>&1 || { echo "$c is already installed" >&2; exit 1; }; done
echo "== $(. /etc/os-release && echo "$PRETTY_NAME"), $(apt-get --version | head -n 1)"

# A clone of the installers with an origin that has no release tag: --update installs its tools, then stops there.
C=/work/clone
mkdir -p "$C"
cp -R /src/setup.sh /src/quickstart.sh /src/install /src/release "$C/"
mkdir -p "$C/supervisor"; cp /src/supervisor/mode-guard.sh "$C/supervisor/"   # setup.sh sources it (--update's version rule)
G=(git -c user.name=t -c user.email=t@example.com -c init.defaultBranch=main)
git init -q --bare /work/origin.git
( cd "$C" && "${G[@]}" init -q && "${G[@]}" add -A && "${G[@]}" commit -qm r && git remote add origin /work/origin.git )

# hold [--past-refresh S] SECS FILE...: holds FILEs' locks for SECS seconds, in the background (its pid in HOLDER), as
# another apt run. --past-refresh S: and then until S seconds after an apt-get update has refreshed the package lists
# (Packages files in /var/lib/apt/lists and nothing holding its lock), however long the mirrors take: kill it when
# what it plays against has returned.
hold() {
  local past=-1 secs ready
  if [ "$1" = --past-refresh ]; then past=$2; shift 2; fi
  secs=$1; shift
  ready=$(mktemp -u /tmp/holder.XXXXXX)
  perl -e '
    use Fcntl qw(O_RDWR O_CREAT F_SETLK F_GETLK F_WRLCK F_UNLCK SEEK_SET);
    my ($secs, $past, $ready, @files) = @ARGV; my @fh;
    my $flock = sub { pack("s s x4 q q i x4", $_[0], SEEK_SET, 0, 0, 0) };   # struct flock (64-bit Linux)
    for my $f (@files) {
      sysopen(my $h, $f, O_RDWR | O_CREAT, 0640) or die "open $f: $!\n";
      fcntl($h, F_SETLK, $flock->(F_WRLCK)) or die "lock $f: $!\n";
      push @fh, $h;
    }
    open(my $r, ">", $ready) or die; close $r;
    sleep $secs;
    exit 0 if $past < 0;
    # F_GETLK only asks (it takes no lock, so apt-get update never finds this holder in its way).
    my $lists = "/var/lib/apt/lists/lock";
    my $refreshed = sub {
      my @lists = glob("/var/lib/apt/lists/*Packages*"); return 0 unless @lists;
      sysopen(my $h, $lists, O_RDWR) or return 0;
      my $fl = $flock->(F_WRLCK);
      fcntl($h, F_GETLK, $fl) or return 0;
      return unpack("s", $fl) == F_UNLCK;
    };
    select(undef, undef, undef, 0.5) until $refreshed->();
    sleep $past;' "$secs" "$past" "$ready" "$@" &
  HOLDER=$!
  local _; for _ in $(seq 50); do [ -e "$ready" ] && return 0; sleep 0.1; done
  echo "the lock holder did not start" >&2; exit 1
}
LISTS=/var/lib/apt/lists/lock ARCHIVES=/var/cache/apt/archives/lock FRONTEND=/var/lib/dpkg/lock-frontend
# upd [DIR]: ./setup.sh --update --native in DIR (the clone); RC, the output in $OUT, the seconds it took in TOOK.
OUT=/work/out
upd() {
  local t=$SECONDS; RC=0
  (cd "${1:-$C}" && GITHUB_REPO=test/repo ./setup.sh --update --native < /dev/null > "$OUT" 2>&1) || RC=$?
  TOOK=$((SECONDS - t))
}
has() { grep -qF -- "$1" "$OUT"; }
count() { grep -cF -- "$1" "$OUT" || true; }

# --- timeout: a copy whose wait is 12 s, saying it still waits every 4 s; the lists lock is held for 90 s.
T=/work/clone-timeout
cp -R "$C" "$T"
grep -q '^MDM_APT_LOCK_WAIT=300$' "$T/install/lib/apt.sh" || { echo "FAIL: install/lib/apt.sh no longer sets MDM_APT_LOCK_WAIT=300" >&2; exit 1; }
grep -q '^MDM_APT_SAY_EVERY=60$' "$T/install/lib/apt.sh" || { echo "FAIL: install/lib/apt.sh no longer sets MDM_APT_SAY_EVERY=60" >&2; exit 1; }
sed -i 's/^MDM_APT_LOCK_WAIT=300$/MDM_APT_LOCK_WAIT=12/; s/^MDM_APT_SAY_EVERY=60$/MDM_APT_SAY_EVERY=4/' "$T/install/lib/apt.sh"
( cd "$T" && "${G[@]}" commit -qam short )
hold 90 "$LISTS"; h=$HOLDER
upd "$T"
kill "$h"; wait "$h" || true
t; { [ "$RC" = 1 ]; } || bad "timeout: --update stops"
t; { [ "$TOOK" -ge 12 ] && [ "$TOOK" -le 40 ]; } || bad "timeout: it waits about 12 s, not more (took ${TOOK}s)"
t; { [ "$(count "waiting for it, up to 12 seconds")" = 1 ]; } || bad "timeout: says it waits, once"
t; { [ "$(count "Still waiting for apt's lock (")" -ge 1 ]; } || bad "timeout: says it still waits"
t; { has "Another apt run still holds apt's lock after 12 seconds. Let it finish (or stop it), then re-run."; } || bad "timeout: says the lock is still held"
t; { has "held by process $h"; } || bad "timeout: apt says which process holds it"
t; { has "Could not refresh the package lists."; } || bad "timeout: names what failed"
t; { has "Nothing was changed." && ! command -v curl > /tmp/ignored; } || bad "timeout: changed nothing"
t; { ! has "failed to refresh"; } || bad "timeout: no 'failed to refresh' (a lock is not a broken source)"
shown timeout

# --- setup: lists lock 15 s, archives lock 35 s, held together; the package lists are empty. The archives lock is also
# held until 10 s after --update's refresh, so its install finds it held however long the mirrors take (a 34 MB Ubuntu
# refresh at 1 MB/s outlasted a plain 35 s).
t; { [ -z "$(find /var/lib/apt/lists -maxdepth 1 -name "*Packages*" -print -quit)" ]; } || bad "setup: the package lists are empty to begin with"
hold 15 "$LISTS"; h1=$HOLDER
hold --past-refresh 10 35 "$ARCHIVES"; h2=$HOLDER
upd
kill "$h2" 2> /tmp/ignored || true   # still waiting for a refresh when --update failed before it
wait "$h1" "$h2" || true
t; { [ "$RC" = 1 ] && has "origin has no release tag"; } || bad "setup: --update gets past its tools to the release lookup"
t; { has "Nothing else was changed (it installed curl ca-certificates openssl above)." && command -v curl > /tmp/ignored && command -v openssl > /tmp/ignored; } || bad "setup: it installed them"
t; { [ "$TOOK" -ge 30 ]; } || bad "setup: it waited for both locks (took ${TOOK}s)"
t; { [ "$(count "waiting for it, up to 5 minutes")" = 2 ]; } || bad "setup: it said so for update and for install"
t; { ! has "failed to refresh"; } || bad "setup: no 'failed to refresh'"
t; { ! has "Unable to locate package"; } || bad "setup: no 'Unable to locate package'"
t; { ! has "Could not get lock"; } || bad "setup: apt's lock lines are not shown while it waits"
t; { has "Setting up curl"; } || bad "setup: apt's output is shown"
t; { [ "$(count "Building dependency tree")" = 1 ] && [ "$(count "Reading state information")" = 1 ]; } || bad "setup: what apt printed before it found the lock held is shown once"
shown setup

# --- quiet: setup.sh's Docker-mode minisign install (the same command line), apt's output hidden.
hold 10 "$FRONTEND"; h=$HOLDER
t=$SECONDS; RC=0
# shellcheck source=/dev/null
( cd "$C" && . ./install/lib/apt.sh && sed -n 's/^    { mdm_apt install -y -qq minisign ||/{ mdm_apt install -y -qq minisign ||/p' setup.sh > /work/quiet.sh \
  && [ -s /work/quiet.sh ] && . /work/quiet.sh ) > "$OUT" 2>&1 || RC=$?
TOOK=$((SECONDS - t)); wait "$h" || true
t; { [ -s /work/quiet.sh ] && [ "$RC" = 0 ]; } || bad "quiet: found setup.sh's minisign install line"
t; { [ "$(count "waiting for it, up to 5 minutes")" = 1 ] && [ "$(wc -l < "$OUT")" = 1 ]; } || bad "quiet: says it waits, though apt's output is hidden"
t; { command -v minisign > /tmp/ignored && [ "$TOOK" -ge 8 ]; } || bad "quiet: then installs minisign (took ${TOOK}s)"
shown quiet

# --- native: the native installer's apt steps on a terminal (python3 missing, everything else stubbed), all three held.
N=/work/clone-native
cp -R "$C" "$N"
grep -q '^exec 3>&-$' "$N/install/install-native.sh" || { echo "FAIL: install-native.sh no longer closes fd 3 after its apt steps" >&2; exit 1; }
sed -i 's/^exec 3>&-$/exec 3>\&-; echo "APT STEPS DONE"; exit 0/' "$N/install/install-native.sh"
mkdir -p /work/stubs /work/jdk/bin
for c in psql pg_ctlcluster mvn aapt; do printf '#!/bin/sh\nexit 0\n' > "/work/stubs/$c"; done
printf '#!/bin/sh\necho "javac 17.0.99"\n' > /work/jdk/bin/javac
chmod +x /work/stubs/* /work/jdk/bin/javac
hold 15 "$LISTS" "$ARCHIVES" "$FRONTEND"; h=$HOLDER
t=$SECONDS; RC=0
if command -v script > /tmp/ignored; then
  script -qfec "env PATH=/work/stubs:$PATH JAVA17_HOME=/work/jdk MDMESH_BASE_URL=http://localhost:8080 bash $N/install/install-native.sh -y" /work/native.typescript < /dev/null > "$OUT" 2>&1 || RC=$?
else
  env PATH="/work/stubs:$PATH" JAVA17_HOME=/work/jdk MDMESH_BASE_URL=http://localhost:8080 bash "$N/install/install-native.sh" -y < /dev/null > "$OUT" 2>&1 || RC=$?
fi
TOOK=$((SECONDS - t)); wait "$h" || true
t; { [ "$RC" = 0 ] && has "APT STEPS DONE" && command -v python3 > /tmp/ignored; } || bad "native: its apt steps succeed"
t; { [ "$TOOK" -ge 13 ]; } || bad "native: it waited (took ${TOOK}s)"
t; { has "waiting for it, up to 5 minutes"; } || bad "native: says it waits, on the terminal"
t; { ! has "failed to refresh"; } || bad "native: no 'failed to refresh'"
t; { [ "$(count "up to 5 minutes")" -le 2 ]; } || bad "native: the lock sentence only for the steps that waited"
shown native

# --- jdk: an update's upgrade of the distribution's JDK 17 (distro_jdk17_upgrade), from the oldest openjdk-17 apt has,
# with apt's messages in German. The native copy above, its update-only step run as an update runs it, the JDK found
# where apt puts it.
# German apt needs a real locale (gettext ignores LANGUAGE under C, and under C.UTF-8 since glibc 2.35) and apt's
# translations, which Ubuntu's images leave out (a dpkg path-exclude): generate en_US.UTF-8, drop the exclude and
# reinstall apt. The case stops unless apt then really speaks German.
for f in /etc/dpkg/dpkg.cfg.d/*; do [ -f "$f" ] && sed -i '/\/usr\/share\/locale/d' "$f"; done
apt-get -qq update > /tmp/jdk-old.log 2>&1
{ apt-get -qq install -y locales && apt-get -qq install -y --reinstall apt; } >> /tmp/jdk-old.log 2>&1 \
  || { cat /tmp/jdk-old.log; echo "FAIL: could not install locales or reinstall apt" >&2; exit 1; }
sed -i 's/^# *\(en_US.UTF-8 UTF-8\)$/\1/' /etc/locale.gen 2>/dev/null || true
grep -qx 'en_US.UTF-8 UTF-8' /etc/locale.gen 2>/dev/null || echo 'en_US.UTF-8 UTF-8' >> /etc/locale.gen
locale-gen >> /tmp/jdk-old.log 2>&1 || { cat /tmp/jdk-old.log; echo "FAIL: locale-gen" >&2; exit 1; }
GERMAN=(env -u LC_ALL LANG=en_US.UTF-8 LANGUAGE=de)
pol=$("${GERMAN[@]}" apt-cache policy apt 2>&1 || true)
grep -q 'Installationskandidat:' <<< "$pol" \
  || { printf '%s\n' "$pol" >&2; echo "FAIL: jdk: apt does not speak German here, so the case would test nothing" >&2; exit 1; }
J=/work/clone-jdk
cp -R "$N" "$J"
# shellcheck disable=SC2016  # the installer's own text
grep -qx '\[ "$UPDATING" != 1 \] || distro_jdk17_upgrade' "$J/install/install-native.sh" \
  || { echo "FAIL: install-native.sh no longer runs distro_jdk17_upgrade on an update" >&2; exit 1; }
# shellcheck disable=SC2016  # the installer's own text
sed -i 's/^\[ "\$UPDATING" != 1 \] || distro_jdk17_upgrade$/distro_jdk17_upgrade/' "$J/install/install-native.sh"
vers=$(apt-cache madison openjdk-17-jdk-headless | awk '{print $3}' | sort -uV)
old=$(head -n 1 <<< "$vers"); new=$(tail -n 1 <<< "$vers")
t; { [ -n "$old" ] && [ "$old" != "$new" ]; } || bad "jdk: apt offers an older openjdk-17 to start from (versions: $(tr '\n' ' ' <<< "$vers"))"
apt-get -qq install -y "openjdk-17-jdk-headless=$old" "openjdk-17-jre-headless=$old" >> /tmp/jdk-old.log 2>&1 \
  || { cat /tmp/jdk-old.log; echo "FAIL: could not install openjdk-17 $old" >&2; exit 1; }
RC=0
"${GERMAN[@]}" PATH="/work/stubs:$PATH" MDMESH_BASE_URL=http://localhost:8080 bash "$J/install/install-native.sh" -y < /dev/null > "$OUT" 2>&1 || RC=$?
# shellcheck disable=SC2016  # a dpkg-query format
now=$(dpkg-query -W -f='${Version}' openjdk-17-jdk-headless)
t; { [ "$RC" = 0 ] && has "APT STEPS DONE"; } || bad "jdk: the apt steps succeed"
t; { [ "$now" = "$new" ] && [ "$(dpkg-query -W -f='${Version}' openjdk-17-jre-headless)" = "$new" ]; } || bad "jdk: openjdk-17 moved from $old to $new (it is $now)"
t; { has "updating the JDK 17 packages ($old → $new)" && has "JDK 17 updated: openjdk-17-jdk-headless openjdk-17-jre-headless $old → $new"; } \
  || bad "jdk: says what it updates, then that it did"
shown jdk

if [ "$FAILS" -gt 0 ]; then echo "apt lock: $FAILS of $CHECKS checks failed" >&2; exit 1; fi
echo "apt lock: all $CHECKS checks passed"
