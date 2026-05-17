# Contributing

mcAI is public for review and contributions, but it is not open source. Before
contributing, read [LICENSE](LICENSE).

## Contribution Terms

By submitting an issue, pull request, patch, comment, or other contribution, you
confirm that:

- you have the right to submit the contribution;
- your contribution does not knowingly include code or content that violates a
  third-party license or agreement;
- you grant XreatLabz a perpetual, worldwide, non-exclusive, royalty-free,
  irrevocable license to use, reproduce, modify, distribute, sublicense, and
  relicense your contribution as part of this project or related XreatLabz
  projects.

Submitting a contribution does not grant you additional rights to use,
redistribute, host, sell, rebrand, or package this project.

## Development Workflow

- Keep this repository source-only.
- Do not commit local Paper runtime state, build outputs, Gradle caches, server
  worlds, generated logs, npm `node_modules`, or bearer tokens.
- Use the Gradle wrapper for plugin verification:

```bash
./gradlew test
./gradlew build
```

- For fleet changes, verify the TypeScript package:

```bash
cd mcai-fleet
npm test
npm run build
```

## Pull Requests

Keep pull requests focused. Include:

- what changed;
- why it changed;
- verification commands and results;
- any behavior or documentation updates.

XreatLabz may accept, reject, modify, or remove contributions at its sole
discretion.
