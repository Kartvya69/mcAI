# AGENTS.md

## Project

mcAI is a Kotlin Paper/Folia plugin that exposes an authenticated MCP HTTP server for Minecraft server administration.

## Required Workflow

- Keep this repository source-only. Do not commit local Paper runtime state, build outputs, Gradle caches, server worlds, generated logs, or bearer tokens.
- Any agent that changes files must commit and push those changes before reporting completion. Keep commits focused, use clear messages, and do not leave intentional edits unstaged, uncommitted, or unpushed unless the user explicitly asks for that.
- Use the Gradle wrapper for verification:
  - `./gradlew test`
  - `./gradlew build`
- Before reporting completion for code or workflow changes, run the relevant Gradle verification and state the exact command result.
- Preserve the MCP security boundaries:
  - all filesystem paths stay jailed to the Minecraft server root
  - MCP bearer auth remains required
  - the MCP HTTP port must differ from the Minecraft gameplay port
  - private-network downloader blocking stays enabled by default
- Keep user-facing docs in sync with public MCP tools and operational behavior.

## Commit Release Rule

Every commit must have a matching GitHub release asset containing the built plugin jar.

This repository intentionally does not use GitHub Actions for jar publishing. The release flow is local:

1. Install tracked hooks once per clone:

   ```bash
   git config core.hooksPath .githooks
   ```

2. On `git commit`, `.githooks/pre-commit` runs `./gradlew test build`.
3. After the commit, `.githooks/post-commit` runs `scripts/release-current-commit.sh`.
4. The release script:
   - builds from a temporary worktree checked out at the exact commit SHA
   - pushes the current branch to `origin`
   - creates or updates tag `commit-<12-char-sha>`
   - uploads `mcAI-<12-char-sha>.jar` as a GitHub release asset
   - when releasing current `HEAD`, moves tag `latest` and uploads `mcAI-latest.jar`

If the post-commit release step fails, fix it before claiming the commit is complete:

```bash
scripts/release-current-commit.sh
```

To backfill a specific commit:

```bash
scripts/release-current-commit.sh <commit-sha>
```

Use `MCAI_SKIP_PRE_COMMIT=1` or `MCAI_SKIP_POST_COMMIT_RELEASE=1` only for emergency local recovery, and mention that exception in the final response.

## Documentation

- `README.md`: project overview and quick start.
- `docs/MCP_TOOLS.md`: public MCP tool contract.
- `docs/OPERATIONS.md`: installation, configuration, verification, and troubleshooting.
- `docs/ARCHITECTURE.md`: component-level architecture notes.
