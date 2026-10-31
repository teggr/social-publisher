#!/usr/bin/env bash
# Exercises the documented JBang launcher end to end without contacting Bluesky.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

printf 'dataDirectory: %s/data\n' "$work" >"$work/social-publisher.yml"
export SOCIAL_PUBLISHER_CONFIG="$work/social-publisher.yml"
run() { jbang "$root/social-publisher.java" "$@"; }

run --help | grep -q "reconcile"
id="$(run draft create --text "JBang smoke test post" | sed -n 's/^Created draft //p')"
[ -n "$id" ]

if run publish "$id" --platform BLUESKY --dry-run >/dev/null 2>&1; then
  echo "dry-run unexpectedly succeeded for an unapproved draft" >&2
  exit 1
fi

run approve "$id" --by smoke-test
run preview "$id" | grep -q "Approval: approved"
run publish "$id" --platform BLUESKY --dry-run | grep -q "no network request was made"
run list | grep -q "$id"
echo "JBang smoke test passed"
