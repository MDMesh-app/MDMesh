#!/usr/bin/env bash
# shellcheck disable=SC2034  # the variables mdm_ports_summary sets are setup.sh's
# Your own domain: a setup.sh re-run never takes the edge's ports 80 and 443 away, and its summary claims only the ones
# the edge publishes. Sourced by setup.sh after install/lib/modes.sh; do not execute it. setup.sh defines err and sets
# pipefail, MODE, HOST and COMPOSE_ARGS.

# mdm_edge_ports: which of the host ports 80 and 443 the edge's container binds, one per line. From its HostConfig, so a
# stopped edge counts. Fails when Docker can't say.
mdm_edge_ports() {
  local ids id p bound=''
  ids=$(docker compose ps -a -q caddy) || return 1
  for id in $ids; do
    bound=$bound$(docker inspect --format '{{json .HostConfig.PortBindings}}' "$id") || return 1
  done
  for p in 80 443; do case "$bound" in *"\"HostPort\":\"$p\""*) echo "$p" ;; esac; done
}

# mdm_list_ports: the host ports this run's compose files publish (any service's), one per line. `config` prints the
# secrets too: they stay in the pipe.
mdm_list_ports() {
  docker compose ${COMPOSE_ARGS[@]+"${COMPOSE_ARGS[@]}"} config | sed -nE 's/^ +published: "?([0-9]+)"?$/\1/p'
}

# mdm_list_has_domain: this run's compose files include the domain overlay, which publishes 80 and 443.
mdm_list_has_domain() {
  local f
  for f in ${COMPOSE_ARGS[@]+"${COMPOSE_ARGS[@]}"}; do
    case "$f" in docker-compose.domain.yml|*/docker-compose.domain.yml) return 0 ;; esac
  done
  return 1
}

# mdm_domain_line: the .env line that adds the domain overlay to the saved list (which, without it, is never empty).
mdm_domain_line() { local l=${COMPOSE_FILE#:}; echo "COMPOSE_FILE='${l%:}:docker-compose.domain.yml'"; }

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
  err "Stopping: the edge publishes port ${has//$'\n'/ and }, and with this install's compose files (COMPOSE_FILE: $COMPOSE_FILE)"
  err "port $lost would close: phones could no longer reach MDMesh there. Nothing was started or stopped: the containers"
  err "run as before (after --update, this checkout is already the new release's)."
  if grep -qxE '80|443' <<< "$list"; then err "To keep port $lost open, publish it in your own compose file."
  else err "To keep it open, set in .env:  $(mdm_domain_line)"; fi
  err "Then run this again, before any update or rollback in the console: until it has run, those close the port too."
  err "To close it instead: docker compose rm -sf caddy (phones can't reach MDMesh until the next run ends), then run this again."
  exit 1
}

# mdm_ports_summary: after `up` in domain mode, sets EDGE_PORTS (mdm_edge_ports; empty when none was found), and ENTRY
# and EXTRA_NOTE to what is published, unless it is both.
mdm_ports_summary() {
  EDGE_PORTS=$(mdm_edge_ports) || EDGE_PORTS=''
  case "$EDGE_PORTS" in
    80*443) ;;
    '') ENTRY="no port 80 or 443 found published"
        if mdm_list_has_domain; then EXTRA_NOTE="Find out why port 80/443 is not published (docker compose ps -a caddy)."
        else EXTRA_NOTE="Unless something of yours forwards ${HOST} to the edge, set in .env  $(mdm_domain_line)  and run ./setup.sh again, before any update or rollback in the console."; fi ;;
    *) ENTRY="port ${EDGE_PORTS}"; EXTRA_NOTE="Make sure ${HOST} resolves to this server and port ${EDGE_PORTS} is open." ;;
  esac
}
