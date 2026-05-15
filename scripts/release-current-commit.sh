#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

if ! command -v gh >/dev/null 2>&1; then
  echo "gh CLI is required to create the commit release" >&2
  exit 1
fi

sha="$(git rev-parse HEAD)"
short_sha="${sha:0:12}"
tag="commit-${short_sha}"
branch="$(git symbolic-ref --quiet --short HEAD || true)"
repo="${MCAI_GITHUB_REPO:-}"

if [[ -z "$repo" ]]; then
  repo="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
fi

tmp_root="$(mktemp -d)"
worktree="$tmp_root/worktree"
artifact="$tmp_root/mcAI-${short_sha}.jar"

cleanup() {
  git worktree remove --force "$worktree" >/dev/null 2>&1 || true
  rm -rf "$tmp_root"
}
trap cleanup EXIT

git worktree add --detach "$worktree" "$sha" >/dev/null

(
  cd "$worktree"
  ./gradlew --no-daemon clean build >/dev/null
)

jar_path="$(find "$worktree/build/libs" -maxdepth 1 -type f -name 'mcAI-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort | head -n 1)"
if [[ -z "$jar_path" || ! -f "$jar_path" ]]; then
  echo "No mcAI jar was produced under build/libs" >&2
  exit 1
fi

cp "$jar_path" "$artifact"

if [[ -n "$branch" ]]; then
  git push origin "$branch" >/dev/null
fi

if gh release view "$tag" --repo "$repo" >/dev/null 2>&1; then
  gh release upload "$tag" "$artifact" --repo "$repo" --clobber
else
  gh release create "$tag" "$artifact" \
    --repo "$repo" \
    --target "$sha" \
    --title "mcAI jar ${short_sha}" \
    --notes "Automated local jar release for commit ${sha}." \
    --prerelease
fi

echo "Created/updated release $tag for $sha"
