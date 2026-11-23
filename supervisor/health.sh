# shellcheck shell=bash
# Sourced by apply.sh and rollback.sh (which define dc and errln): when a recreated stack counts as healthy.
#
# healthy: the server answers HEALTH_URL within HEALTH_TIMEOUT seconds, and the edge (caddy, which serves the console,
# /recovery and every device request) is running and stays up (edge_steady). The server alone is not enough: an edge
# that can't load its configuration (an operator's site.d rule the new release refuses, or docker/edge-peers.sh
# refusing the proxy settings) exits at start and is restarted over and over, while the server answers fine.
healthy() {
  local deadline=$(( $(date +%s) + HEALTH_TIMEOUT )) up=0
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if curl -fsS -m 5 "$HEALTH_URL" >/dev/null 2>&1; then up=1; break; fi
    sleep 5
  done
  [ "$up" = 1 ] || return 1
  edge_steady || return 1
  # And the server still answers, after the edge's settle time (three tries, 2 s apart: one slow answer is no failure).
  local out i
  for i in 1 2 3; do
    # (and the edge is still the run edge_steady saw: it may have stopped during these tries)
    if out=$(curl -fsS -m 5 "$HEALTH_URL" 2>&1); then edge_same; return; fi
    [ "$i" = 3 ] || sleep "${HEALTH_RETRY_WAIT:-2}"
  done
  errln "the server stopped answering $HEALTH_URL (${out:0:200})"; return 1
}

# edge_steady: the edge container is running, and still the same run EDGE_SETTLE seconds later (default 5; Docker
# restarts a container that exits within a second of starting well inside that). Mode-independent: it doesn't need to
# know which port or name the edge answers on. Says why when it isn't.
edge_steady() {
  local id first second
  EDGE_RUN=''
  id=$(dc ps -a -q caddy 2>/dev/null | head -1)
  if [ -n "$id" ]; then
    first=$(docker inspect --format '{{.State.Status}} {{.State.StartedAt}}' "$id" 2>/dev/null) || first=''
    sleep "${EDGE_SETTLE:-5}"
    second=$(docker inspect --format '{{.State.Status}} {{.State.StartedAt}}' "$id" 2>/dev/null) || second=''
    if [ "${first%% *}" = running ] && [ "$first" = "$second" ]; then EDGE_RUN="$id $second"; return 0; fi
  fi
  edge_down "${second:-${first:-no container}}"
}

# edge_same: the edge is still the run edge_steady last saw (EDGE_RUN), with no wait.
edge_same() {
  local now=''
  [ -z "$EDGE_RUN" ] || now=$(docker inspect --format '{{.State.Status}} {{.State.StartedAt}}' "${EDGE_RUN%% *}" 2>/dev/null) || now=''
  [ -n "$now" ] && [ "${EDGE_RUN%% *} $now" = "$EDGE_RUN" ] && return 0
  edge_down "${now:-no container}"
}

edge_down() {
  errln "the edge (caddy) did not stay up ($1): see 'docker compose logs caddy' in the install directory; a rule of your own in site.d/ is the usual cause"
  return 1
}
