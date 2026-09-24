#!/usr/bin/env bash
# Opens or updates a tracking issue for a failed scheduled/deep job.
# Env: TIER, LABELS, TITLE, DRY_RUN, GITHUB_REPOSITORY, GITHUB_WORKFLOW, GITHUB_JOB,
#      GITHUB_SERVER_URL, GITHUB_RUN_ID, GITHUB_TOKEN (gh reads it), GITHUB_OUTPUT.
set -euo pipefail
title="${TITLE:-}"
[ -n "$title" ] || title="CI ${TIER} failure: ${GITHUB_WORKFLOW}/${GITHUB_JOB}"
run_url="${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"
body="Scheduled or deep-tier job failed.

- Run: ${run_url}
- Workflow: ${GITHUB_WORKFLOW}
- Job: ${GITHUB_JOB}
- Tier: ${TIER}

Opened automatically by init-lunacy/ci-kit report-tier-failure (policy rule 6)."
if [ "${DRY_RUN:-false}" = "true" ]; then
  echo "DRY RUN title=$title"; echo "$body"; echo "issue-url=dry-run" >> "${GITHUB_OUTPUT:-/dev/null}"; exit 0
fi
existing=$(gh issue list -R "$GITHUB_REPOSITORY" --state open --search "\"$title\" in:title" --json number,title \
  --jq ".[] | select(.title == \"$title\") | .number" | head -1)
if [ -n "$existing" ]; then
  gh issue comment "$existing" -R "$GITHUB_REPOSITORY" --body "Failed again: ${run_url}"
  url="${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/issues/${existing}"
else
  for l in ${LABELS//,/ }; do gh label create "$l" -R "$GITHUB_REPOSITORY" --force >/dev/null 2>&1 || true; done
  url=$(gh issue create -R "$GITHUB_REPOSITORY" --title "$title" --label "$LABELS" --body "$body")
fi
echo "issue-url=$url" >> "${GITHUB_OUTPUT:-/dev/null}"
echo "$url"
