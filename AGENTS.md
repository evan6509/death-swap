# Death Swap development

- Target Minecraft 26.2 (default) and 26.3, Fabric, and Java 25.
- Build both targets sequentially with separate project caches; select 26.3 with `DEATHSWAP_MINECRAFT=26.3`. Keep the Docker deployment on 26.2.
- Use `codex/development` as the long-lived working branch, matching the user's other repositories.
- Commit routine implementation and fixes on `codex/development`; propose integration through a reviewed pull request into `main`.
- Do not merge into, push directly to, or publish from `main` without explicit user approval.
- The user has explicitly authorized the checked-in workflow to publish automatically after successful pushes to `main`, including approved PR merges. No separate publication approval is required for that workflow. Agents still need explicit approval to merge, push directly to `main`, or manually publish releases or remote tags.
- Preserve unrelated local edits. Inspect Git status and the current branch before changing files.
- Validate gameplay changes with `./gradlew --no-daemon build runGametest` and `python3 scripts/verify_mod_jar.py`.
- For network/deployment changes, also run `python3 scripts/verify_proxy_jar.py`, `python3 docker/test_runtime.py`, and `sh -n docker/entrypoint.sh`. Never test reset logic against existing player worlds; use disposable fixtures/deployments.
- Keep build, automated server GameTest, connected-client gameplay, and release evidence distinct.
