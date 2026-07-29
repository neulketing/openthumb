# Contributing

OpenThumb is a fork of [OpenMinis](https://github.com/OpenMinis/OpenMinis), an
on-device AI agent for Android and iOS. This fork adds fleet tooling and carries
its own fixes; everything else comes from upstream.

## Send it upstream first

If the bug or feature is not specific to this fork — anything in the agent loop,
providers, the sandbox, the UI, iOS — please report it to
[OpenMinis](https://github.com/OpenMinis/OpenMinis) instead. Fixes landed there
reach far more users and flow back into this fork on the next sync.

Bring it here when it is about fork-specific code: `tools/openthumb-fleet`, the
rebranded package (`com.fug.openthumb`), or a fix upstream has declined.
Note that upstream does not accept pull requests — it is a release mirror — so a
patch rejected there on those grounds is still welcome here.

## Building

See [BUILDING.md](BUILDING.md). Short version for Android:

```sh
./deps/build_proot.sh
./scripts/prepare_android_sandbox.sh
cd src/android && ./gradlew :app:assembleDebug
```

You need JDK 17, the Android SDK (compileSdk 36), NDK r28+ with
`$ANDROID_NDK_HOME` set, and CMake 3.22.1. Only `arm64-v8a` is built, so use an
arm64 device or emulator image.

CI (`.github/workflows/android.yml`) runs the same sequence on every push and
pull request against `main`.

## The JNI / package-rename trap

JNI entry points encode the Java package path in the C symbol name:
`com.fug.openthumb.sandbox.PtyBridge.forkExec` is exported from
`src/android/app/src/main/cpp/pty_bridge.c` as
`Java_com_neulketing_openthumb_sandbox_PtyBridge_forkExec`.

So renaming the package means renaming those C/C++ symbols in lockstep. Miss it
and **nothing fails at build time** — the APK links, installs and starts, then
the shell dies the moment it tries to bind the native method. `scripts/rebrand.sh`
rewrites both sides together; if you rename by hand, grep `Java_com_` under
`src/android/app/src/main/cpp/` and fix every hit.

## Commit messages

Conventional commits: `feat:`, `fix:`, `docs:`, `chore:`, `test:`.

```
fix: guard Android 10 notification API behind SDK_INT check
```

## Licence

OpenThumb is GPL-3.0. Contributions are accepted under the same licence — by
opening a pull request you agree your changes ship under GPL-3.0.
