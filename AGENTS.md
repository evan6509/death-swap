# Death Swap development

- Target Minecraft 26.2, Fabric, and Java 25.
- Use `codex/development` as the long-lived working branch, matching the user's other repositories.
- Commit routine implementation and fixes on `codex/development`; propose integration through a reviewed pull request into `main`.
- Do not merge into, push directly to, or publish from `main` without explicit user approval.
- Preserve unrelated local edits. Inspect Git status and the current branch before changing files.
- Validate gameplay changes with `./gradlew --no-daemon build runGametest` and `python3 scripts/verify_mod_jar.py`.
- Keep build, automated server GameTest, connected-client gameplay, and release evidence distinct.
