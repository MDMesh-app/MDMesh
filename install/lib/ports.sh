#!/usr/bin/env bash
# Your own domain: a setup.sh re-run stops rather than close the edge's ports 80 and 443. Sourced by setup.sh after
# install/lib/modes.sh; do not execute it. setup.sh defines err and sets pipefail, MODE and COMPOSE_ARGS.

# mdm_edge_ports: which of the host ports 80 and 443 the edge's container binds, one per line. From its HostConfig, so a
# stopped edge counts. Fails when Docker can't say.
mdm_edge_ports() {
  local ids bound p
  ids=$(docker compose ps -a -q caddy) || return 1
  [ -n "$ids" ] || return 0
  # shellcheck disable=SC2086  # one id per word
  bound=$(docker inspect --format '{{json .HostConfig.PortBindings}}' $ids) || return 1
  for p in 80 443; do case "$bound" in *"\"HostPort\":\"$p\""*) echo "$p" ;; esac; done
}

# mdm_list_ports: the host ports this run's compose files publish (any service's), one per line. `config` prints the
# secrets too: they stay in the pipe.
mdm_list_ports() {
  docker compose ${COMPOSE_ARGS[@]+"${COMPOSE_ARGS[@]}"} config | sed -nE 's/^ +published: "?([0-9]+)"?$/\1/p'
}

# mdm_list_has_domain: the list has the domain overlay, which publishes 80 and 443 (a re-run without a list adds it).
mdm_list_has_domain() { case ":${COMPOSE_FILE:-docker-compose.domain.yml}:" in *[:/]docker-compose.domain.yml:*) return 0 ;; esac; return 1; }

# mdm_ports_guard: on a re-run in domain mode, stops it when the edge binds host port 80 or 443 and this run's compose
# files no longer publish it. A list with the domain overlay needs no asking. When Docker or compose can't answer, it
# stops too: `up` reads the same files, and would fail on them after everything before it.
mdm_ports_guard() {
  local has list p lost=''
  [ "$MODE" = 2 ] || return 0
  ! mdm_list_has_domain || return 0
  has=$(mdm_edge_ports) || { err "Could not ask Docker which ports the edge (caddy) publishes (above). Nothing was started or stopped."; exit 1; }
  [ -n "$has" ] || return 0
  list=$(mdm_list_ports 2>&1) || { err "Could not read the compose files COMPOSE_FILE names ($COMPOSE_FILE). Nothing was started or stopped. docker compose says:
$list"; exit 1; }
  for p in $has; do grep -qx "$p" <<< "$list" || lost="${lost:+$lost and }$p"; done
  [ -n "$lost" ] || return 0
  err "Stopping: with this install's compose files (COMPOSE_FILE: $COMPOSE_FILE) the edge's port $lost would close, and"
  err "phones could no longer reach MDMesh there. Nothing was started or stopped."
  err "To keep it open, publish it in your own compose file or set in .env:  COMPOSE_FILE='$COMPOSE_FILE:docker-compose.domain.yml'"
  err "Then run this again, before any update or rollback in the console: until it has run, those close the port too."
  err "To close it instead: docker compose rm -sf caddy (phones can't reach MDMesh until the next run ends), then run this again."
  exit 1
}
