# Release

Operational runbook for cutting, recovering, and publishing OpenRose releases. For versioning rules and user-side verification, see `CONTRIBUTING.md#发布` and `docs/adr/0015-single-version-source.md`; for workflow mechanics, see `.github/workflows/ci.yml`.

## Prerequisites

- Confirm the latest `CI / Release` run on `main` is green.
- Signed Android release builds require four repository Actions secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Provisioning or rotating secrets is human-only; keep secret values out of chat and logs.

## Cut a release

1. Inspect the open `release-please` PR (`chore: release X.Y.Z`) and confirm `version.txt`, `.release-please-manifest.json`, `CHANGELOG.md`, `Cargo.toml`, and `Cargo.lock` match the intended release (`release-please` updates `version.txt` and `Cargo.toml`, and the `release-please` job runs `cargo xtask sync-version` on the PR branch so `Cargo.toml` and `Cargo.lock` stay equal to `version.txt`).
   - An `action_required` (0s) status on the release PR is GitHub's normal `GITHUB_TOKEN` anti-recursion guard, not a CI failure. Use the latest `main` `CI / Release` run as the green gate.
2. Squash-merge the release PR (`gh pr merge <N> --squash --delete-branch`).
3. Wait for the `main` `CI / Release` run triggered by the merge commit to finish. It creates tag `vX.Y.Z`, drafts the GitHub Release, builds all packages, generates `SHA256SUMS` and SLSA provenance, and uploads them to the draft.

## Recover a failed release build

- **Secret or transient failure (no code change)**: after the cause is fixed, rerun only the failed jobs (`gh run rerun <run-id> --failed`) to reuse already-built artifacts.
- **Workflow or code fix while still a draft**: push the fix to `main`, then move the unpublished draft tag to the new commit (`git tag -f vX.Y.Z HEAD && git push -f origin vX.Y.Z`) so `CI / Release` rebuilds and overwrites the draft assets with `--clobber`.
- **Published releases**: treat published tags as immutable; cut a follow-up version instead of moving the tag.

## Publish

1. Verify the draft release (`gh release view vX.Y.Z`) contains all expected assets: signed `.apk`, `.msi`, `.deb`, `.pacman`, `.rpm`, `.AppImage`, and `SHA256SUMS`.
2. Publish only with explicit user confirmation:
   - Pre-release: `gh release edit vX.Y.Z --draft=false --prerelease`
   - Full release: `gh release edit vX.Y.Z --draft=false`
