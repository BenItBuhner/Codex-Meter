# Contributing

## Local setup

Install JDK 17 or newer and Android SDK Platform 36 with Build Tools 36.x. Set
`ANDROID_SDK_ROOT` or `ANDROID_HOME` to the SDK directory.

The OneUI-Design dependencies are hosted on GitHub Packages. Export `GH_USERNAME`
and a `GH_ACCESS_TOKEN` with `read:packages` access before running a full build or
Android lint.

Run the core checks, release build, and Android lint with:

```bash
./run-tests.sh
./build.sh
./lint.sh
```

## Changes

- Keep commits focused and descriptive.
- Add or update tests with behavior changes.
- Do not commit credentials, tokens, or generated build artifacts.
