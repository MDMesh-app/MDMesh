#!/usr/bin/env bash
# setup.sh's .env reader. Source this file; do not execute it.
#
# mdm_load_env FILE sets and exports each variable FILE assigns, exactly as `set -a; . FILE` would, without running
# anything: the file is parsed, never sourced. It reads every plain shell assignment a person might write in a .env:
#   NAME=value  NAME="value"  NAME='value'  export NAME=value  NAME=a'b c'"d"  NAME=a\ b  NAME="a\"b\$c\\d"
# with blanks, # comment lines and a trailing " # comment", and several assignments on one line. Inside "…" a backslash
# escapes only $ ` " and \ (as in bash). Anything whose value bash would compute rather than read (a $ or ` expansion,
# a leading ~ or one after a ":"), a line bash would read as a command (NAME=value word, ;, |, &, <, >, (, )), an
# unterminated quote or a line that continues on the next one (a trailing backslash), a carriage return (a Windows line
# ending), a read-only shell variable, or anything that is not an assignment stops the installer with the line number.
# It never prints a value (a .env holds the database password and other secrets), and xtrace is off while it runs.

# _mdm_le_fail N WHY: stop, naming line N of the file being read.
_mdm_le_fail() {
  printf 'MDMesh: %s, line %s: %s. The value is not shown. Fix that line, then re-run.\n' "$_mdm_le_file" "$1" "$2" >&2
  exit 1
}

mdm_load_env() { local _mdm_le_x=$-; set +x; _mdm_load_env "$1"; case "$_mdm_le_x" in *x*) set -x ;; esac; }

# shellcheck disable=SC1003  # '\' is a backslash, on purpose
_mdm_load_env() {
  local _mdm_le_file=$1 _mdm_le_line _mdm_le_n=0 _mdm_le_i _mdm_le_len _mdm_le_c _mdm_le_d _mdm_le_key \
    _mdm_le_val _mdm_le_start _mdm_le_colon _mdm_le_j
  local _mdm_le_az=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_ _mdm_le_09=0123456789
  while IFS= read -r _mdm_le_line || [ -n "$_mdm_le_line" ]; do
    _mdm_le_n=$((_mdm_le_n + 1))
    _mdm_le_len=${#_mdm_le_line}; _mdm_le_i=0
    # Leading blanks; then a blank or comment line is skipped.
    while [ "$_mdm_le_i" -lt "$_mdm_le_len" ]; do
      case "${_mdm_le_line:_mdm_le_i:1}" in ' '|$'\t') _mdm_le_i=$((_mdm_le_i + 1)) ;; *) break ;; esac
    done
    [ "$_mdm_le_i" -lt "$_mdm_le_len" ] || continue
    [ "${_mdm_le_line:_mdm_le_i:1}" != '#' ] || continue
    case "$_mdm_le_line" in *$'\r'*) _mdm_le_fail "$_mdm_le_n" "it has a carriage return (a Windows line ending)" ;; esac
    # An "export " prefix.
    case "${_mdm_le_line:_mdm_le_i}" in
      'export '*|'export'$'\t'*)
        _mdm_le_i=$((_mdm_le_i + 6))
        while case "${_mdm_le_line:_mdm_le_i:1}" in ' '|$'\t') true ;; *) false ;; esac; do _mdm_le_i=$((_mdm_le_i + 1)); done ;;
    esac
    while :; do
      # NAME=
      _mdm_le_j=$_mdm_le_i
      case "${_mdm_le_line:_mdm_le_i:1}" in [$_mdm_le_az]) ;; *) _mdm_le_fail "$_mdm_le_n" "it is not a NAME=value assignment" ;; esac
      while case "${_mdm_le_line:_mdm_le_i:1}" in [$_mdm_le_az$_mdm_le_09]) true ;; *) false ;; esac; do _mdm_le_i=$((_mdm_le_i + 1)); done
      [ "${_mdm_le_line:_mdm_le_i:1}" = '=' ] || _mdm_le_fail "$_mdm_le_n" "it is not a NAME=value assignment"
      _mdm_le_key=${_mdm_le_line:_mdm_le_j:_mdm_le_i-_mdm_le_j}
      case "$_mdm_le_key" in _mdm_le_*) _mdm_le_fail "$_mdm_le_n" "the name $_mdm_le_key is reserved for the .env reader" ;; esac
      if [[ $(declare -p "$_mdm_le_key" 2>/dev/null) =~ ^declare\ -[^\ ]*r ]]; then
        _mdm_le_fail "$_mdm_le_n" "$_mdm_le_key is a read-only shell variable"
      fi
      _mdm_le_i=$((_mdm_le_i + 1)); _mdm_le_start=$_mdm_le_i; _mdm_le_val=''; _mdm_le_colon=0
      # The value: one word, of unquoted, '…' and "…" parts.
      while [ "$_mdm_le_i" -lt "$_mdm_le_len" ]; do
        _mdm_le_c=${_mdm_le_line:_mdm_le_i:1}
        case "$_mdm_le_c" in
          ' '|$'\t') break ;;
          "'")
            _mdm_le_j=$((_mdm_le_i + 1))
            while [ "$_mdm_le_j" -lt "$_mdm_le_len" ] && [ "${_mdm_le_line:_mdm_le_j:1}" != "'" ]; do _mdm_le_j=$((_mdm_le_j + 1)); done
            [ "$_mdm_le_j" -lt "$_mdm_le_len" ] || _mdm_le_fail "$_mdm_le_n" "a ' quote is not closed on that line"
            _mdm_le_val+=${_mdm_le_line:_mdm_le_i+1:_mdm_le_j-_mdm_le_i-1}; _mdm_le_i=$((_mdm_le_j + 1)); _mdm_le_colon=0 ;;
          '"')
            _mdm_le_i=$((_mdm_le_i + 1))
            while :; do
              [ "$_mdm_le_i" -lt "$_mdm_le_len" ] || _mdm_le_fail "$_mdm_le_n" 'a " quote is not closed on that line'
              _mdm_le_c=${_mdm_le_line:_mdm_le_i:1}
              case "$_mdm_le_c" in
                '"') _mdm_le_i=$((_mdm_le_i + 1)); break ;;
                '$'|'`') _mdm_le_fail "$_mdm_le_n" "the value has a \$ or a \` (the shell would expand it: escape it as \\\$ or \\\`, or use '…')" ;;
                '\')
                  _mdm_le_d=${_mdm_le_line:_mdm_le_i+1:1}
                  case "$_mdm_le_d" in
                    '') _mdm_le_fail "$_mdm_le_n" "it ends in a backslash, which continues it on the next line" ;;
                    '$'|'`'|'"'|'\') _mdm_le_val+=$_mdm_le_d; _mdm_le_i=$((_mdm_le_i + 2)) ;;
                    *) _mdm_le_val+='\'; _mdm_le_i=$((_mdm_le_i + 1)) ;;
                  esac ;;
                *) _mdm_le_val+=$_mdm_le_c; _mdm_le_i=$((_mdm_le_i + 1)) ;;
              esac
            done
            _mdm_le_colon=0 ;;
          '\')
            _mdm_le_d=${_mdm_le_line:_mdm_le_i+1:1}
            [ -n "$_mdm_le_d" ] || _mdm_le_fail "$_mdm_le_n" "it ends in a backslash, which continues it on the next line"
            _mdm_le_val+=$_mdm_le_d; _mdm_le_i=$((_mdm_le_i + 2)); _mdm_le_colon=0 ;;
          '$'|'`') _mdm_le_fail "$_mdm_le_n" "the value has a \$ or a \` (the shell would expand it: escape it as \\\$ or \\\`, or use '…')" ;;
          ';'|'&'|'|'|'<'|'>'|'('|')') _mdm_le_fail "$_mdm_le_n" "the value has an unquoted ; & | < > ( or ) (quote the value)" ;;
          '~')
            if [ "$_mdm_le_i" = "$_mdm_le_start" ] || [ "$_mdm_le_colon" = 1 ]; then
              _mdm_le_fail "$_mdm_le_n" "the value has a ~ that the shell would expand to a home directory (quote it)"
            fi
            _mdm_le_val+='~'; _mdm_le_i=$((_mdm_le_i + 1)); _mdm_le_colon=0 ;;
          ':') _mdm_le_val+=':'; _mdm_le_i=$((_mdm_le_i + 1)); _mdm_le_colon=1 ;;
          *) _mdm_le_val+=$_mdm_le_c; _mdm_le_i=$((_mdm_le_i + 1)); _mdm_le_colon=0 ;;
        esac
      done
      printf -v "$_mdm_le_key" '%s' "$_mdm_le_val"
      # shellcheck disable=SC2163  # the variable named by $_mdm_le_key
      export "$_mdm_le_key"
      # Then blanks, and the end of the line, a # comment, or another NAME=value.
      while case "${_mdm_le_line:_mdm_le_i:1}" in ' '|$'\t') true ;; *) false ;; esac; do _mdm_le_i=$((_mdm_le_i + 1)); done
      [ "$_mdm_le_i" -lt "$_mdm_le_len" ] || break
      [ "${_mdm_le_line:_mdm_le_i:1}" != '#' ] || break
      _mdm_le_j=$_mdm_le_i
      while case "${_mdm_le_line:_mdm_le_j:1}" in [$_mdm_le_az$_mdm_le_09]) true ;; *) false ;; esac; do _mdm_le_j=$((_mdm_le_j + 1)); done
      if [ "$_mdm_le_j" = "$_mdm_le_i" ] || [ "${_mdm_le_line:_mdm_le_j:1}" != '=' ]; then
        _mdm_le_fail "$_mdm_le_n" "something follows the value that the shell would run as a command (quote the whole value)"
      fi
    done
  done < "$_mdm_le_file"
}
