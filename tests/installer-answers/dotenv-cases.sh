#!/usr/bin/env bash
# .env lines for tests/installer-answers/run.sh's dotenv check. Sourced. ACCEPT: lines mdm_load_env must read exactly as
# `set -a; . FILE` does (each is compared with bash). REFUSE: lines it must stop on, naming the line number and never
# the value (each value holds the marker SECRETVALUE, which must not appear in its output).
# shellcheck disable=SC2034,SC2016,SC1003  # read by run.sh; the $, ` and \ are the cases
ACCEPT=(
  'K=v'
  'K="v"'
  "K='v'"
  'K="v" # comment'
  "K='v' # comment"
  'K="v" '
  "K='v'   "
  "K='a'\"b\""
  'K="a\"b"'
  'K="a\$b"'
  'K=a\ b'
  'K="a\\b"'
  'K="a\b"'
  "K='a\\b'"
  'K='
  'K=""'
  "K=''"
  'export K=v'
  '  K=v'
  'K=v # comment'
  'K=a#b'
  'K=a=b'
  'K="a b  c"'
  'K=x A=y'
  'K="a\`b"'
  "K='it'\\''s'"
  'K=a\"b'
  'K=https://x.example.com/a:b'
  'K="multi # not a comment"'
  'K=*.glob'
  'K=a\\b'
  '# a comment line'
  ''
  '   '
  $'K="tab\tin"'
  $'K=v\t# tab before the comment'
  'TRUSTED_PROXIES="173.245.48.0/20 103.21.244.0/22" # Cloudflare'
  "K=a'b c'd"
  'K=[x]'
  'K={a,b}'
  'K="a"#b'
  'K=a~b'
  'K=!x'
  'SMTP_PASSWORD="p\$ss\"w\\rd"'
  'K=a}'
  'K=Ünïcødé'
  'LC_ALL=C'
  $'# a comment with a Windows line ending\r'

)
REFUSE=(
  'K="${}"SECRETVALUE'
  'K=$SECRETVALUE'
  'K="$SECRETVALUE"'
  'K=`SECRETVALUE`'
  'K=$(SECRETVALUE)'
  'K="SECRETVALUE" b'
  'K=SECRETVALUE;b'
  'K=SECRETVALUE|b'
  'K=SECRETVALUE&'
  'K=SECRETVALUE>b'
  'K=(SECRETVALUE)'
  'K="SECRETVALUE'
  "K='SECRETVALUE"
  'K=SECRETVALUE\'
  'K=~/SECRETVALUE'
  'K=a:~SECRETVALUE'
  'K+=SECRETVALUE'
  '1K=SECRETVALUE'
  'SECRETVALUE'
  'export SECRETVALUE'
  $'K=SECRETVALUE\r'
  '_mdm_le_line=SECRETVALUE'
  'UID=1SECRETVALUE'
  "K=\$'SECRETVALUE'"
  'K="a\${SECRETVALUE}"x$y'
)
