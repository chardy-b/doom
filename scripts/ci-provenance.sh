#!/usr/bin/env bash
# Shared exact-candidate contract. This file is sourced and deliberately defines
# `sha` for callers (notably ci-fixture.sh) after all checks have passed.

[[ "${GITHUB_ACTIONS:-}" == true ]] || {
  printf '%s\n' 'Android CI execution is restricted to GitHub Actions.' >&2
  return 2 2>/dev/null || exit 2
}

candidate=${CANDIDATE_SHA:?Exact candidate required}
[[ "$candidate" =~ ^[0-9a-f]{40}$ ]] || {
  printf '%s\n' 'Candidate SHA must be exactly 40 lowercase hexadecimal characters.' >&2
  return 2 2>/dev/null || exit 2
}
sha=$(git rev-parse --verify HEAD)

provenance_failure=0
[[ "$sha" == "$candidate" ]] || provenance_failure=1
git diff --quiet -- || provenance_failure=1
git diff --cached --quiet -- || provenance_failure=1
[[ -z "$(git ls-files --others --exclude-standard)" ]] || provenance_failure=1
if (( provenance_failure )); then
  printf '%s\n' 'Exact-candidate provenance failed; path/status metadata follows.' >&2
  # Names and status codes only. Never inspect or print file contents.
  git status --short --untracked-files=all >&2 || true
  return 2 2>/dev/null || exit 2
fi
