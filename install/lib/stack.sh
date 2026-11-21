#!/usr/bin/env bash
# One MDMesh stack per Docker project name (setup.sh sources this file; quickstart.sh keeps a verbatim copy of the
# functions between the markers below, which CI checks). Source it; do not execute it. The installers define err.
# bash 3.2 compatible (quickstart may run under macOS's bash).
#
# Every install's .env says COMPOSE_PROJECT_NAME=mdmesh, so the quick start or setup.sh run in a second directory would
# address the first install's containers: compose would stop them and recreate them with the new directory's secrets
# against the first install's database (production down). And a new .env over the volumes of an install whose .env is
# gone gives the server a password the database never had. Both are refused here, before any compose command.

# >>> stack.sh functions (quickstart.sh keeps a verbatim copy) >>>
# mdm_stack_guard PROJECT NEW: stops the installer when Docker already has a stack of PROJECT that is not this
# directory's. Its containers' com.docker.compose.project.working_dir labels name their directory: any other than this
# one is refused, naming it. (Containers the updater recreated say /project, its own mount of the install directory:
# they don't count either way.) With NEW=1 (no .env here yet: the installer writes a new one) any container or volume of
# PROJECT is refused, since a new .env's secrets can't work with them; NEW=0 (this directory's .env is used) allows this
# directory's own. Fails closed when Docker can't be asked.
mdm_stack_guard() {
  local project=$1 new=$2 here dirs d other='' ours=0 n=0 vols
  here=$(pwd -P)
  dirs=$(docker ps -a --filter "label=com.docker.compose.project=$project" \
    --format '{{.Label "com.docker.compose.project.working_dir"}}') \
    || { err "Could not list Docker's containers (docker ps failed, above): not starting anything. Nothing was changed."; exit 1; }
  while IFS= read -r d; do
    [ -n "$d" ] || continue
    n=$((n + 1))
    [ "$d" != /project ] || continue
    if [ "$d" -ef "$here" ]; then ours=1; else other="$other$d
"; fi
  done <<EOF
$dirs
EOF
  other=$(printf '%s' "$other" | sort -u | tr '\n' ' ' | sed 's/ $//')
  if [ -n "$other" ] || { [ "$n" -gt 0 ] && { [ "$new" = 1 ] || [ "$ours" = 0 ]; }; }; then
    if [ -n "$other" ]; then
      err "An MDMesh install already exists in $other (Docker project $project)."
    elif [ "$ours" = 1 ]; then
      err "The containers of an MDMesh install in this directory exist, but its .env is gone (Docker project $project)."
    else
      err "An MDMesh install already exists on this machine (Docker project $project; 'docker compose ls' shows where)."
    fi
    err "Starting another here would stop it and take over its containers. To update it, use Settings → Updates in its"
    err "console, or run ./setup.sh --update in its directory if it is a clone. Nothing was changed."
    exit 1
  fi
  [ "$new" = 1 ] || return 0
  vols=$(docker volume ls -q --filter "label=com.docker.compose.project=$project") \
    || { err "Could not list Docker's volumes (docker volume ls failed, above): not starting anything. Nothing was changed."; exit 1; }
  vols=$(printf '%s' "$vols" | tr '\n' ' ' | sed 's/ *$//')
  if [ -n "$vols" ]; then
    err "Docker still has the data of an MDMesh install (volumes: $vols), with none of its containers. Its database"
    err "keeps the password in that install's .env: run 'docker compose up -d' in that install's directory to start it"
    err "again. To start over instead, remove them: docker volume rm $vols (this deletes ALL of that install's data)."
    err "Nothing was changed."
    exit 1
  fi
}
# <<< stack.sh functions <<<
