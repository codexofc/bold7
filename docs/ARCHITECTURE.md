# Code architecture

Bold7 keeps a clean code architecture suited to the project: tested and testable, developed test-first (TDD), under the Apache 2.0 license. Rules specific to a directory of the code are given in `CONTRIBUTING.md`.

What makes Bold7 unusual, and what the architecture must serve:
- the code runs inside system processes (the launcher as recents provider, SystemUI) for months, on **a single device** that must never be left unusable;
- smoothness is the first goal, so the main thread does nothing but rendering;
- most dependencies are Android services (binder, providers, roles) that no JVM test can run.

## 1. A pure Kotlin core, Android at the edge

The logic that decides (sorting and filtering in the Hub, notification profiles, MIME parsing, speed dial, home screen state) is written **without any `android.*` import**: pure functions and classes, tested on the JVM in a few milliseconds. The Android edge (views, binder, providers, `SurfaceControl`) stays thin: it translates system events for the core and applies what the core decides.

Corollary: when a file mixes a computation and an Android call, the computation moves out into a pure function next to it.

## 2. System services behind an interface Bold7 owns, and only them

Every system service a piece of logic depends on (quickstep binder, `PackageManager`, SMS provider, call log, Telecom, `NotificationListenerService`) is reached through **a narrow interface that Bold7 owns**, with two implementations: the real one, and a hand-written *fake* for tests. This is the only exception to the rule "no interface for a single implementation": the fake is the second one.

No mocking library. A fake can be read; a mock describes calls.

## 3. Immutable state, events, reducer

Every screen or feature that holds state (home screen, Hub, task list) has:
- an **immutable state** (`data class`);
- **events** (gesture, data received, tap);
- a **pure reducer** `(State, Event) -> State`, tested on the JVM;
- an edge that runs the effects (binder call, database write) and feeds their results back as events.

Views render the state and decide nothing. In the launcher, the state is a `StateFlow` collected on the main thread, which only draws.

## 4. Threads are part of the types

- Dispatchers are **injected** (`main`, `io`, `default`), never hard-coded: a test passes a `TestDispatcher` and controls time.
- Window animations run on **a dedicated surface thread** (`HandlerThread`), also injected.
- StrictMode crashes debug builds (`core/platform`, `enableStrictModeIfDebuggable()`).

## 5. Hand-written injection

No Hilt, Dagger or Koin: one hand-written graph per process (`LauncherGraph`, `HubGraph`...), which builds objects through their constructors. No annotation processing, no startup cost, everything can be read.

## 6. Visible errors

No catch-all `catch`. A failure at the edge becomes a typed result, or a counter exposed in the Bold7 diagnostics and logged under the tag `Bold7/<domain>`. A SystemUI hook that fails to apply is counted and shows up.

## 7. Direction of dependencies between modules

```
apps/*  ──►  core/*
system/sysui  ──►  core/*
core/ui  ──►  core/design
apps/launcher, apps/*, tools/essai  ──►  core/ui (view components)
apps/launcher, system/sysui  ──►  core/icons (package-to-icon table)
core/hub-data, core/mail  ──►  (nothing from apps/ or system/)
```

- `core/*` never depends on `apps/*` or `system/*`.
- `core/platform` is **the only** module that touches hidden APIs and the device stubs.
- **No Compose, anywhere**, by design: classic views in the launcher, SystemUI and every app. Components are written once, as views, in `core/ui`, shared by the launcher and the apps.

The Gradle module graph enforces these rules: a forbidden dependency cannot be declared.

## 8. Tests

| Level | Tool | For what | Where |
|---|---|---|---|
| JVM unit tests | `kotlin.test` on JUnit 4, `kotlinx-coroutines-test` | all pure logic, reducers, computations | `src/test/` of each module, `./gradlew test` |
| Integration on the Q25 | `androidx.test` | the real implementations of the system interfaces (providers, roles, binder), few of them | `src/androidTest/` |
| Performance | `tools/perf.sh`, Macrobenchmark | the performance budgets (`docs/BASELINE.md`), baseline profiles | on the Q25, never the emulator |

**TDD** for all logic: the failing test first, then the minimal code that makes it pass, then the cleanup. A test must fail if the logic breaks; no tests of accessors. Exploratory spikes are throwaway and do not follow TDD: what they prove becomes a test in the real implementation.

Test tooling enters module by module, with the first change that puts logic in it: `testImplementation(kotlin("test-junit"))` in its `build.gradle.kts` (the Kotlin bundled with AGP does not pick the JUnit variant of `kotlin-test` on its own). `kotlinx-coroutines-test` comes with the first test that uses coroutines.

## 9. License

Code under **Apache 2.0** (`LICENSE`, `NOTICE`), the license of AOSP and of the Thunderbird backend that Bold7 reuses. Code taken from AOSP keeps its original header and is added to `NOTICE`. APK dependencies stay under Apache 2.0, MIT or BSD. BlackBerry assets are never in the repository and are not covered by the license.
