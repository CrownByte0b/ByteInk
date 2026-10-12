#!/usr/bin/env bash
# Prints the ID of a run in this repository that still holds every named artifact, so a workflow
# started alone can reuse earlier outputs. "latest" takes the newest such run on the current ref,
# then on DEFAULT_BRANCH. Runs of forks never qualify. The choice is logged and summarized.
set -euo pipefail
usage='Usage: artifacts-run.sh <run-id|latest> <artifact>...'
requested=${1:?$usage}
shift
(( $# )) || { echo "$usage" >&2; exit 1; }
repo=${GITHUB_REPOSITORY:?}

# Succeeds when run $1 holds every artifact named by the remaining arguments.
holds() {
  local available name
  available=$(gh api --paginate "repos/$repo/actions/runs/$1/artifacts?per_page=100" \
    --jq '.artifacts[] | select((.expired | not) and .workflow_run.head_repository_id == .workflow_run.repository_id) | .name') || exit 1
  for name in "${@:2}"; do
    grep -qxF -- "$name" <<<"$available" || return 1
  done
}

run=''
if [[ $requested == latest ]]; then
  branches=("${GITHUB_REF_NAME:?}")
  if [[ -n ${DEFAULT_BRANCH:-} && $DEFAULT_BRANCH != "$GITHUB_REF_NAME" ]]; then branches+=("$DEFAULT_BRANCH"); fi
  for branch in "${branches[@]}"; do
    candidates=$(BRANCH=$branch gh api --paginate -X GET "repos/$repo/actions/artifacts" -f name="$1" -f per_page=100 \
      --jq '[.artifacts[] | select((.expired | not) and .workflow_run.head_branch == env.BRANCH and .workflow_run.head_repository_id == .workflow_run.repository_id) | .workflow_run.id] | sort | reverse | .[]') || exit 1
    for candidate in $candidates; do
      if holds "$candidate" "$@"; then run=$candidate; break 2; fi
    done
  done
  [[ -n $run ]] || { echo "No run on ${branches[*]} still holds: $*" >&2; exit 1; }
elif [[ $requested =~ ^[1-9][0-9]*$ ]]; then
  holds "$requested" "$@" || { echo "Run $requested does not hold all of: $*" >&2; exit 1; }
  run=$requested
else
  echo "Expected a run ID or latest, got: $requested" >&2
  exit 1
fi

origin=$(gh api "repos/$repo/actions/runs/$run" \
  --jq '"[\(.name) #\(.run_number)](\(.html_url)), \(.event) on \(.head_branch) at \(.head_sha[0:7]), \(.conclusion // .status)"')
# shellcheck disable=SC2016 # The backticks are Markdown code spans, not command substitution.
printf -v names '`%s`, ' "$@"
printf 'Using %s from %s\n' "${names%, }" "$origin" >&2
if [[ -n ${GITHUB_STEP_SUMMARY:-} ]]; then printf -- '- %s from %s\n' "${names%, }" "$origin" >> "$GITHUB_STEP_SUMMARY"; fi
printf '%s\n' "$run"
