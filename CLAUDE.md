# Clock: droid fork

A personal fork of BlackyHawky/Clock, maintained as a droid app: modified the Android way and shipped
as signed GitHub releases. The full conventions and workflows (change, sync, upstream PR, build, release)
live in the droid skill; load it with `/droid` before any branch, sync, signing or release work. The
ground rules below hold in every session, skill loaded or not.

## This repo

- `origin` is the fork (Staninna/Clock), `upstream` is the original (BlackyHawky/Clock).
- `my-main` holds all fork work and is the GitHub default branch. `main` mirrors `upstream/main`.
- Install mode: replace. The fork keeps upstream's application ID (`com.best.deskclock`).
- Release build: `:app:assembleRelease`, JDK 21. `.github/workflows/release.yml` is the only release
  path.
- The app has no unit tests, so check alarm behavior on an emulator. A clock that fails to ring or
  dismiss is the worst bug this app can ship.

## Ground rules

- **Commit to `my-main`**, directly or through a short-lived branch merged into it. Upstream reaches
  `my-main` by merge. `my-main` is published, so its history only grows: merge, never rebase.
- **`main` only fast-forwards from `upstream/main`.** If it has diverged, stop and ask.
- **A push to `my-main` is a release.** CI tags it `v<versionName>-fork.<n>` and publishes signed
  APKs to users. Push only when the user asks and the work is verified. Pushes that only touch the
  `paths-ignore` list in `release.yml` (docs, `*.md`) or carry `[skip ci]` don't release.
- **Verify before committing:** build the debug variant and run the unit tests. Before pushing, also
  build the minified release task above: R8 problems only show up there.
- **Tags:** only `-fork.<n>` tags, so they never clash with upstream's. Pushed tags stay where they
  are; a failed release is fixed by pushing a fix to `my-main`.
- **Signing keys are hands-off.** The key lives outside the repo; only the droid scripts
  (`signing-key.sh`, `build-signed.sh`) touch it. Never read, print or create keystores (`*.jks`,
  `*.keystore`, `keystore.properties`) or anything in the release-keys folder.
- **Commit identity:** the GitHub noreply address in the repo's local git config. Before committing,
  confirm `git config --local user.email` ends in `@users.noreply.github.com`.
- **Small, focused changes** in the app's own style and architecture.
