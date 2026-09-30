#!/usr/bin/env bash
# Shared BASE_URL and hostname rules for the installers (setup.sh, install/install-native.sh; quickstart.sh keeps a verbatim copy of
# the functions between the markers below, which CI checks). Source this file; do not execute it. bash 3.2 compatible
# (quickstart may run under macOS's bash).
#
# BASE_URL is the public address devices and the console use. It is written into .env, which three readers parse (bash
# sourcing it in setup.sh, docker compose, and a person), into Tomcat's ROOT.xml as an XML attribute value, and into the
# enrollment QR. So it is an ALLOWLIST, not a list of bad characters: anything a reader could expand, strip or execute
# ($, `, \, ;, quotes, spaces, …) never gets in. A BASE_URL must be:
#   - http:// or https://, in any letter case (RFC 3986: the scheme is case-insensitive); it is rewritten in lowercase;
#   - then a host: a name or IPv4 address (letters, digits, "." and "-"; not starting or ending with "." or "-", no
#     ".."), or a [bracketed] IPv6 address (hex digits, ":" and "."), optionally followed by :port (digits);
#   - then optionally a /path of letters, digits and . _ : / + = , -   (no "~": after a ":" in an assignment, bash
#     expands ~ and ~user, so `. ./.env` would read https://x/a:~/b as https://x/a:/root/b)
# Refused outright: user info (user@host), a query (?) or fragment (#), and "%" (percent-escapes are not accepted; a
# base URL has no need for them).
# Not checked: "." and ".." path segments (a /path is used as it is), each host label's own syntax, the IPv6 address
# beyond its characters and colons, and the port's range. This only validates: escaping the values written into
# ROOT.xml is a separate job, done where they are written.

# >>> url.sh functions (quickstart.sh keeps a verbatim copy) >>>
# The character sets, spelled out: a range such as [A-Za-z0-9] follows the locale's collation, and under en_US.UTF-8
# bash matches thousands of non-ASCII letters with it (https://ｅxample.com would pass).
_MDM_ALNUM=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789
_MDM_HEX=0123456789ABCDEFabcdef
_MDM_DIGITS=0123456789
# _mdm_hostport_problem HOSTPORT: prints why HOSTPORT is not host[:port] as described above; prints nothing when it is.
_mdm_hostport_problem() {
  local hp=$1 host='' port='' v6=''
  case "$hp" in
    \[*)
      v6=${hp#\[}
      case "$v6" in *\]*) ;; *) echo "\"$hp\" has no closing ]"; return ;; esac
      port=${v6#*\]}; v6=${v6%%\]*}
      case "$v6" in
        ''|*[!${_MDM_HEX}:.]*|*:::*|*::*::*|*:*:*:*:*:*:*:*:*) echo "\"[$v6]\" is not an IPv6 address"; return ;;
        *:*) ;;
        *) echo "\"[$v6]\" is not an IPv6 address"; return ;;
      esac
      case "$port" in '') return ;; :*) port=${port#:}; [ -n "$port" ] || port=- ;; *) echo "\"$hp\": only :port may follow ]"; return ;; esac ;;
    *)
      host=${hp%%:*}
      case "$hp" in *:*) port=${hp#*:}; [ -n "$port" ] || port=- ;; esac
      case "$host" in
        '') echo 'it has no host (expected e.g. mdm.example.com)'; return ;;
        *[!${_MDM_ALNUM}.-]*|.*|*.|-*|*-|*..*) echo "\"$host\" is not a host name or IPv4 address"; return ;;
      esac ;;
  esac
  case "$port" in
    '') ;;                                                                     # no port
    *[!${_MDM_DIGITS}]*) echo "\"$hp\" does not end in a port number after the colon"; return ;;
    0*) echo "\"$hp\" has an invalid port (a port is 1-65535, with no leading zero)"; return ;;
  esac
  # Length first, so the numeric compare never sees a value too big for the shell's integer.
  [ -z "$port" ] || { [ "${#port}" -le 5 ] && [ "$port" -ge 1 ] && [ "$port" -le 65535 ]; } \
    || echo "\"$hp\" has a port outside 1-65535"
}

# mdm_check_base_url VAR: checks the URL held in the variable named VAR. When it passes, VAR's scheme is rewritten in
# lowercase (HTTPS://… becomes https://…; nothing else changes) and it succeeds. Otherwise it prints why to stderr,
# leaves VAR alone and fails. It takes a variable name, not the value, so the caller's variable is normalised in place.
mdm_check_base_url() {
  local url=${!1-} rest='' bad='' why=''
  case "$url" in
    '')                             why='it is empty' ;;
    *@*)                            why='it contains "@": a base URL takes no user name or password (user@host)' ;;
    *\?*|*#*)                       why='it contains "?" or "#": a base URL takes no query or fragment' ;;
    *%*)                            why='it contains "%": percent-escapes are not accepted in a base URL' ;;
    *[!${_MDM_ALNUM}._:/+=,\[\]-]*)
      bad=${url//[${_MDM_ALNUM}._:\/+=,\[\]-]/}; bad=${bad:0:1}
      case "$bad" in \') bad="\"'\"" ;; [[:print:]]) bad="'$bad'" ;; *) bad=$(printf '%q' "$bad") ;; esac
      why="it contains $bad, which is not allowed (only letters, digits and . _ : / + = , [ ] -)" ;;
    [Hh][Tt][Tt][Pp]://*)           rest=${url#*://}; url="http://$rest" ;;
    [Hh][Tt][Tt][Pp][Ss]://*)       rest=${url#*://}; url="https://$rest" ;;
    *)                              why='it must start with http:// or https:// (e.g. https://mdm.example.com)' ;;
  esac
  case "$rest" in
    [Hh][Tt][Tt][Pp]://*|[Hh][Tt][Tt][Pp][Ss]://*) why='it has the scheme twice (where a hostname is asked for, enter the name only)' ;;
  esac
  [ -n "$why" ] || why=$(_mdm_hostport_problem "${rest%%/*}")
  if [ -z "$why" ]; then printf -v "$1" '%s' "$url"; return 0; fi
  url=${!1-}
  case "$url" in *[![:print:]]*) url=$(printf '%q' "$url") ;; esac   # shown as typed, unless that would garble the terminal
  printf 'Invalid public base URL %s: %s.\n' "${url:-(empty)}" "$why" >&2
  return 1
}
# mdm_check_host VAR: checks that the variable named VAR holds a bare host[:port] as described above, with no scheme,
# path, user@, query or fragment: what the hostname prompts ask for (it becomes BASE_URL and Caddy's site address).
# Otherwise it prints why to stderr and fails. The value is never changed.
mdm_check_host() {
  local h=${!1-} bad='' why=''
  case "$h" in
    '')                 why='it is empty' ;;
    *://*)              why='enter the name only, without http:// or https://' ;;
    */*|*\?*|*#*|*@*)   why='enter the name only: no /path, ?query, #fragment or user@' ;;
    *[!${_MDM_ALNUM}.:\[\]-]*)
      bad=${h//[${_MDM_ALNUM}.:\[\]-]/}; bad=${bad:0:1}
      case "$bad" in \') bad="\"'\"" ;; [[:print:]]) bad="'$bad'" ;; *) bad=$(printf '%q' "$bad") ;; esac
      why="it contains $bad, which is not allowed (only letters, digits, . and -, or an [IPv6] address, then an optional :port)" ;;
  esac
  [ -n "$why" ] || why=$(_mdm_hostport_problem "$h")
  [ -z "$why" ] && return 0
  case "$h" in *[![:print:]]*) h=$(printf '%q' "$h") ;; esac
  printf 'Invalid hostname %s: %s.\n' "${h:-(empty)}" "$why" >&2
  return 1
}
# <<< url.sh functions <<<
