#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
mapfile -t git_env_vars < <(git rev-parse --local-env-vars)
for git_env_var in "${git_env_vars[@]}"; do
  unset "$git_env_var"
done
cd "$repo_root"

if ! command -v gh >/dev/null 2>&1; then
  echo "gh CLI is required to create the commit release" >&2
  exit 1
fi

target_ref="${1:-HEAD}"
sha="$(git rev-parse "$target_ref")"
short_sha="${sha:0:12}"
tag="commit-${short_sha}"
branch="$(git symbolic-ref --quiet --short HEAD || true)"
head_sha="$(git rev-parse HEAD)"
update_latest=false
repo="${MCAI_GITHUB_REPO:-}"

if [[ "$sha" == "$head_sha" ]]; then
  update_latest=true
fi

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
  gh release edit "$tag" \
    --repo "$repo" \
    --target "$sha" \
    --title "mcAI jar ${short_sha}" \
    --notes "Automated local jar release for commit ${sha}." \
    --prerelease >/dev/null
  gh release upload "$tag" "$artifact" --repo "$repo" --clobber
else
  gh release create "$tag" "$artifact" \
    --repo "$repo" \
    --target "$sha" \
    --title "mcAI jar ${short_sha}" \
    --notes "Automated local jar release for commit ${sha}." \
    --prerelease
fi

if [[ "$update_latest" == "true" ]]; then
  latest_artifact="$tmp_root/mcAI-latest.jar"
  cp "$artifact" "$latest_artifact"

  git tag -f latest "$sha" >/dev/null
  git push origin refs/tags/latest --force >/dev/null

  if gh release view latest --repo "$repo" >/dev/null 2>&1; then
    gh release edit latest \
      --repo "$repo" \
      --target "$sha" \
      --title "Latest mcAI jar" \
      --notes "Moving latest jar release for commit ${sha}." \
      --prerelease >/dev/null
    gh release upload latest "$latest_artifact" --repo "$repo" --clobber
  else
    gh release create latest "$latest_artifact" \
      --repo "$repo" \
      --target "$sha" \
      --title "Latest mcAI jar" \
      --notes "Moving latest jar release for commit ${sha}." \
      --prerelease
  fi
fi

echo "Created/updated release $tag for $sha"
if [[ "$update_latest" == "true" ]]; then
  echo "Updated latest release for $sha"
fi
