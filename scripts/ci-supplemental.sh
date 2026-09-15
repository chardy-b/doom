#!/usr/bin/env bash
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || exit 2
cd "$(dirname "$0")/.."
. scripts/ci-provenance.sh
mkdir -p evidence
overlay_exit=0
bash scripts/ci-overlay.sh || overlay_exit=$?
printf 'overlay_exit=%s\n' "$overlay_exit" >> evidence/supplemental-context.txt
printf 'overlay_exit=%s\n' "$overlay_exit" > evidence/overlay-exit.txt
fixture_exit=0
bash scripts/ci-fixture.sh || fixture_exit=$?
printf 'fixture_exit=%s\n' "$fixture_exit" >> evidence/supplemental-context.txt
printf 'fixture_exit=%s\n' "$fixture_exit" > evidence/fixture-exit.txt
printf 'Supplemental results: overlay_exit=%s fixture_exit=%s\n' "$overlay_exit" "$fixture_exit"
(( overlay_exit == 0 )) || exit "$overlay_exit"
exit "$fixture_exit"
