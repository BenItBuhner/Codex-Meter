# Codex Meter for Android

Native Android + Wear OS client for viewing the Codex allowance attached to a
signed-in ChatGPT account. This directory is the **Android** Gradle project of
the Codex Meter monorepo. The iOS client lives under [`../ios/`](../ios/).

## Layout

| Path | Role |
|------|------|
| `app/` | Phone app (`dev.bennett.codexmeter`) |
| `wear/` | Wear OS companion |
| `shared/` | Shared phone↔watch contracts |
| `tests/` | Pure-Java self-tests used by `./run-tests.sh` |
| `vendor/m2/` | Cached One UI / SESL Maven artifacts |
| `ci/` | Encrypted release keystore material for GitHub Actions |

## Build and test

From this `android/` directory (or via the repo-root wrappers):

```bash
./run-tests.sh
./lint.sh
./build.sh
```

Requirements: JDK 17+, Android SDK Platform 36, Build Tools 36.x, and
`ANDROID_SDK_ROOT` / `ANDROID_HOME`. `vendor/m2` covers SESL deps offline;
optional `GH_USERNAME` / `GH_ACCESS_TOKEN` refresh GitHub Packages.

Signed local APKs land in `android/dist/`. See the repository root
[`README.md`](../README.md) for product notes and release tagging.
